package com.lmreader.core.vision

import com.lmreader.core.model.*
import org.junit.Test
import kotlin.test.*

class SegTextRegionsTest {
    @Test fun `coarse caption merges close rows by default and zero gap separates them`() {
        val parent = text("caption",PixelRect(20f,20f,440f,130f))
        val first = DetectedTextLine(PixelRect(40f,35f,420f,65f),.9f)
        val second = DetectedTextLine(PixelRect(40f,73f,420f,103f),.9f)
        val seg = page(parent).copy(textLines = listOf(first,second))
        assertEquals(1,selectSegRegions(seg,SegTextScope.FREE_TEXT).size)
        val separate = selectSegRegions(seg,SegTextScope.FREE_TEXT,0f)
        assertEquals(2,separate.size)
        assertTrue(separate[0].bounds.bottom <= separate[1].bounds.top)
        assertEquals(listOf(first),selectRegionTextLines(separate[0],seg.regions,seg.textLines))
        assertEquals(listOf(second),selectRegionTextLines(separate[1],seg.regions,seg.textLines))
        assertEquals(1,selectSegRegions(seg,SegTextScope.FREE_TEXT,.3f).size)
        val ocr = LocalOcrResult("p",500,500,LocalOcrLanguage.ENGLISH,listOf(
            OcrLine("first",first.bounds,"FIRST",.9f),OcrLine("second",second.bounds,"SECOND",.9f)),0)
        assertEquals(listOf("FIRST","SECOND"),groupPageText(seg,ocr,SegTextScope.ALL,0f).map { it.sourceText })
        val mergedText = groupPageText(seg,ocr).single().sourceText
        assertTrue(mergedText.contains("FIRST") && mergedText.contains("SECOND"))
        assertEquals(1,groupPageText(seg,ocr,SegTextScope.ALL,.3f).size)
    }

    @Test fun `vertical captions split into columns and overlapping padded rows do not share crops`() {
        val column = page(text("columns",PixelRect(20f,20f,130f,450f))).copy(textLines=listOf(
            DetectedTextLine(PixelRect(35f,40f,65f,420f),.9f),DetectedTextLine(PixelRect(73f,40f,103f,420f),.9f)))
        assertEquals(1,selectSegRegions(column,SegTextScope.FREE_TEXT).size)
        val parts = selectSegRegions(column,SegTextScope.FREE_TEXT,0f)
        assertEquals(2,parts.size)
        assertTrue(parts[0].bounds.right <= parts[1].bounds.left)
        assertEquals(1,selectSegRegions(column,SegTextScope.FREE_TEXT,.3f).size)
        val overlapping = page(text("rows",PixelRect(20f,20f,440f,130f))).copy(textLines=listOf(
            DetectedTextLine(PixelRect(40f,35f,420f,70f),.9f),DetectedTextLine(PixelRect(40f,65f,420f,100f),.9f)))
        val rows = selectSegRegions(overlapping,freeTextMergeGapRatio=0f)
        assertEquals(2,rows.size)
        assertTrue(rows[0].bounds.bottom <= rows[1].bounds.top)
    }

    @Test fun `free text merging control never splits normal speech or drops a no-line candidate`() {
        val speech = bubble("speech",PixelRect(20f,20f,440f,130f))
        val lines = listOf(DetectedTextLine(PixelRect(40f,35f,420f,65f),.9f),DetectedTextLine(PixelRect(40f,73f,420f,103f),.9f))
        assertEquals(listOf(speech),selectSegRegions(page(speech).copy(textLines=lines)))
        assertEquals("caption",selectSegRegions(page(text("caption",PixelRect(20f,200f,440f,250f)))).single().id)
    }
    @Test fun `overlapping full text and fragments keep a single complete balloon crop`() {
        val b=bubble("speech",PixelRect(40f,30f,220f,470f))
        val block=text("block",PixelRect(85f,80f,175f,400f))
        val fragment=text("fragment",PixelRect(130f,100f,175f,310f))
        val seg=page(b,block,fragment)
        assertEquals(listOf(b),selectSegRegions(seg,SegTextScope.BUBBLES))
    }
    @Test fun `neighboring vertical columns belong to one balloon`() {
        val b=bubble("speech",PixelRect(40f,30f,220f,470f))
        val right=text("right",PixelRect(140f,80f,175f,320f))
        val left=text("left",PixelRect(85f,80f,120f,400f))
        assertEquals(listOf(b),selectSegRegions(page(b,right,left),SegTextScope.BUBBLES))
    }
    @Test fun `partial caption prediction cannot cut the complete detected line`() {
        val partial=text("partial",PixelRect(30f,100f,210f,135f))
        val line=DetectedTextLine(PixelRect(25f,98f,330f,140f),.95f)
        val seg=supplementFreeTextRegions(page(partial).copy(textLines=listOf(line)))
        val target=selectSegRegions(seg,SegTextScope.FREE_TEXT).single()
        assertEquals(listOf(line),selectRegionTextLines(target,seg.regions,seg.textLines))
        assertTrue(target.bounds.left<=line.bounds.left && target.bounds.right>=line.bounds.right)
    }
    @Test fun `tight free text mask cannot reject a padded OCR line or API target`() {
        val bounds=PixelRect(30f,30f,200f,80f)
        val region=text("bold",bounds).copy(contour=listOf(
            PixelPoint(50f,45f),PixelPoint(180f,45f),PixelPoint(180f,60f),PixelPoint(50f,60f)))
        val line=DetectedTextLine(bounds,.9f)
        val seg=page(region).copy(textLines=listOf(line))
        assertEquals(listOf(line),selectRegionTextLines(selectSegRegions(seg).single(),seg.regions,seg.textLines))
        assertEquals("bold",groupPageText(seg,LocalOcrResult("p",500,500,LocalOcrLanguage.ENGLISH,
            listOf(OcrLine("line",bounds,"BOLD",.9f)),0)).single().id)
    }
    @Test fun `crop refinement targets missing and small lines without filtering API candidates`() {
        val small = text("small",PixelRect(30f,30f,150f,50f))
        val missing = bubble("missing",PixelRect(200f,30f,350f,160f))
        val large = text("large",PixelRect(30f,300f,450f,360f))
        val seg = page(small,missing,large).copy(width=2000,height=2000,textLines = listOf(
            DetectedTextLine(small.bounds,.95f),DetectedTextLine(large.bounds,.95f)))
        assertEquals(setOf("small","missing"),textRefinementRegions(seg).map { it.id }.toSet())
        assertEquals(3,selectSegRegions(seg).size)
        assertTrue(textRefinementRegions(page(bubble("page",PixelRect(0f,0f,500f,500f)))).isEmpty())
    }
    @Test fun `detector-only caption retains ragged line shape and full OCR lines`() {
        val upper = PixelRect(30f,30f,200f,60f)
        val lower = PixelRect(60f,80f,180f,110f)
        val seg = supplementFreeTextRegions(page().copy(textLines = listOf(
            DetectedTextLine(upper,.9f),DetectedTextLine(lower,.9f))))
        val region = selectSegRegions(seg, freeTextMergeGapRatio = 1.2f).single()
        assertTrue(region.contour.size >= 8)
        assertTrue(polygonContains(region.contour,35f,40f))
        assertFalse(polygonContains(region.contour,35f,95f))
        assertEquals(2,selectRegionTextLines(region,seg.regions,seg.textLines).size)
    }
    private fun bubble(id: String, bounds: PixelRect, contour: List<PixelPoint> = emptyList()) = SegRegion(id, RegionKind.BUBBLE, bounds, .9f, contour)
    private fun text(id: String, bounds: PixelRect) = SegRegion(id, RegionKind.FREE_TEXT, bounds, .9f)
    private fun page(vararg regions: SegRegion) = SegResult("p", 500, 500, regions.toList(), 0, "test")

    @Test fun `three scopes classify interior text before filtering`() {
        val b = bubble("bubble", PixelRect(0f, 0f, 150f, 150f))
        val inside = text("inside", PixelRect(30f, 30f, 100f, 70f))
        val outside = text("outside", PixelRect(200f, 40f, 300f, 70f))
        val seg = page(b, inside, outside)
        assertEquals(listOf("bubble"), selectSegRegions(seg, SegTextScope.BUBBLES).map { it.id })
        assertEquals(listOf("outside"), selectSegRegions(seg, SegTextScope.FREE_TEXT).map { it.id })
        assertEquals(2, selectSegRegions(seg).size)
        assertEquals(b.bounds, selectSegRegions(seg).first().bounds)
        assertNull(selectSegRegions(seg).first().extractionBounds)
    }
    @Test fun `one merged balloon retains both interior text blocks as independent targets`() {
        val b = bubble("connected", PixelRect(0f, 0f, 400f, 200f))
        val left = text("left", PixelRect(40f, 40f, 150f, 100f))
        val right = text("right", PixelRect(230f, 70f, 350f, 130f))
        val seg = page(b, left, right)
        val targets = selectSegRegions(seg, SegTextScope.BUBBLES)
        assertEquals(2, targets.size)
        assertTrue(targets.all { it.kind == RegionKind.BUBBLE })
        assertEquals(0f, overlap(targets[0].bounds, targets[1].bounds))
        assertEquals(targets.map { it.id }, selectSegRegions(seg).map { it.id })
        val lines = listOf(OcrLine("l", left.bounds, "FIRST", .9f), OcrLine("r", right.bounds, "SECOND", .9f))
        val grouped = groupPageText(seg, LocalOcrResult("p", 500, 500, LocalOcrLanguage.ENGLISH, lines, 0))
        assertEquals(listOf("FIRST", "SECOND"), grouped.map { it.sourceText })
        assertEquals(targets.map { it.id }, grouped.map { it.id })
    }
    @Test fun `caption in a balloon bounding rectangle but outside its mask stays free`() {
        val b = bubble("triangle", PixelRect(0f, 0f, 200f, 200f), listOf(PixelPoint(0f, 0f), PixelPoint(200f, 0f), PixelPoint(0f, 200f)))
        val outside = text("caption", PixelRect(140f, 140f, 190f, 180f))
        val inside = text("speech", PixelRect(20f, 20f, 100f, 50f))
        assertEquals(listOf("caption"), selectSegRegions(page(b, outside, inside), SegTextScope.FREE_TEXT).map { it.id })
    }
    @Test fun `adjacent overlapping balloons survive deduplication`() {
        val a = bubble("a", PixelRect(0f, 0f, 200f, 200f))
        val b = bubble("b", PixelRect(65f, 0f, 265f, 200f))
        assertTrue(iou(a.bounds, b.bounds) > .45f)
        assertFalse(duplicateSegBounds(a.bounds, b.bounds))
        assertEquals(2, keepSegRegions(listOf(a, b)).size)
        assertEquals(1, keepSegRegions(listOf(a, a.copy(id = "duplicate", bounds = PixelRect(2f, 2f, 202f, 202f)))).size)
    }
    @Test fun `nested merged prediction cannot suppress smaller occupied balloons`() {
        val outer = bubble("outer", PixelRect(0f, 0f, 400f, 250f))
        val a = bubble("a", PixelRect(0f, 0f, 180f, 200f))
        val b = bubble("b", PixelRect(210f, 0f, 400f, 200f))
        val seg = page(outer, a, b, text("ta", PixelRect(30f, 30f, 150f, 80f)), text("tb", PixelRect(250f, 30f, 350f, 80f)))
        assertEquals(3, keepSegRegions(listOf(outer, a, b)).size)
        assertEquals(listOf("a", "b"), selectSegRegions(seg, SegTextScope.BUBBLES).map { it.id })
    }
    @Test fun `free-only grouping does not resurrect speech from excluded balloons`() {
        val b = bubble("b", PixelRect(0f, 0f, 200f, 200f))
        val ocr = LocalOcrResult("p", 500, 500, LocalOcrLanguage.ENGLISH,
            listOf(OcrLine("speech", PixelRect(30f, 30f, 100f, 60f), "SPEECH", .9f)), 0)
        assertTrue(groupPageText(page(b), ocr, SegTextScope.FREE_TEXT).isEmpty())
    }
    @Test fun `clipped child contours cannot erase the neighboring text block`() {
        val points = listOf(PixelPoint(0f, 0f), PixelPoint(400f, 0f), PixelPoint(400f, 200f), PixelPoint(0f, 200f))
        val bounds = PixelRect(30f, 20f, 120f, 100f)
        val result = clipContour(points, bounds)
        assertEquals(bounds, contourBounds(result))
        assertTrue(result.all { it.x.isFinite() && it.y.isFinite() })
    }
    @Test fun `text detector adds outside captions without turning speech into free text`() {
        val b = bubble("triangle", PixelRect(0f, 0f, 200f, 200f), listOf(PixelPoint(0f, 0f), PixelPoint(200f, 0f), PixelPoint(0f, 200f)))
        val speech = DetectedTextLine(PixelRect(20f, 20f, 100f, 50f), .95f)
        val caption = DetectedTextLine(PixelRect(140f, 140f, 190f, 180f), .9f)
        val seg = supplementFreeTextRegions(page(b).copy(textLines = listOf(speech, caption)))
        val free = selectSegRegions(seg, SegTextScope.FREE_TEXT).single()
        assertEquals(RegionKind.FREE_TEXT, free.kind)
        assertEquals(listOf(caption), selectRegionTextLines(free, seg.regions, seg.textLines))
        assertEquals(listOf(speech), selectRegionTextLines(b, seg.regions, seg.textLines))
        assertEquals(listOf("triangle"), selectSegRegions(seg, SegTextScope.BUBBLES).map { it.id })
    }
    @Test fun `detected caption lines form one target and existing blocks are not duplicated`() {
        val first = DetectedTextLine(PixelRect(220f, 200f, 420f, 230f), .9f)
        val second = DetectedTextLine(PixelRect(230f, 245f, 410f, 275f), .8f)
        val known = text("known", PixelRect(200f, 30f, 450f, 90f))
        val seg = supplementFreeTextRegions(page(known).copy(textLines = listOf(first, second,
            DetectedTextLine(PixelRect(220f, 40f, 420f, 70f), .95f))))
        val free = selectSegRegions(seg, SegTextScope.FREE_TEXT, 1.2f)
        assertEquals(2, free.size)
        assertEquals("known", free.first().id)
        assertEquals(listOf(first, second), selectRegionTextLines(free.last(), seg.regions, seg.textLines))
        assertEquals(.8f, free.last().confidence)
        assertEquals(seg, supplementFreeTextRegions(seg))
    }
    @Test fun `cached lines are restricted to the selected child and clipped to its crop`() {
        val parent = bubble("parent", PixelRect(0f, 0f, 400f, 200f))
        val left = text("left", PixelRect(30f, 40f, 120f, 90f))
        val right = text("right", PixelRect(230f, 40f, 350f, 90f))
        val seg = page(parent, left, right)
        val target = selectSegRegions(seg, SegTextScope.BUBBLES).first()
        val crossing = DetectedTextLine(PixelRect(20f, 45f, 115f, 85f), .9f)
        val neighbor = DetectedTextLine(right.bounds, .95f)
        val selected = selectRegionTextLines(target, seg.regions, listOf(crossing, neighbor)).single()
        assertEquals(target.bounds.left, selected.bounds.left)
        assertEquals(crossing.bounds.right, selected.bounds.right)
    }
}
