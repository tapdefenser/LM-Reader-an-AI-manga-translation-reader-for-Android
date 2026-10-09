package com.lmreader.core.vision

import android.graphics.*
import android.text.TextPaint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BubbleMaskRendererTest {
    @Test fun defaultMaskOpacityIsEightyFivePercent() {
        val source = Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val region = PageTextRegion("mask",RegionKind.FREE_TEXT,PixelRect(20f,20f,80f,80f),emptyList(),"",emptyList())
        val output = source.copy(Bitmap.Config.ARGB_8888,true)
        try {
            val settings = BubbleRenderSettings(fillMode=BubbleFillMode.WHITE)
            assertEquals(85,settings.opacityPercent)
            BubbleMaskRenderer().prepareSource(source,listOf(region)).layout(listOf(PageTranslatedRegion(region,"")),settings).draw(Canvas(output))
            assertEquals(216,Color.red(output.getPixel(40,40)))
            assertEquals(Color.BLACK,output.getPixel(5,5))
        } finally { source.recycle(); output.recycle() }
    }
    @Test fun wrappedParagraphDoesNotShrinkToMakeInvisibleTrailingSpacesFit() {
        val text = "A long paragraph with several sentences. It should fill the available frame and stay readable.\nThe second paragraph keeps its line break."
        val layout = BubbleMaskRenderer().fitLayout(text, 384, 244, TextPaint(Paint.ANTI_ALIAS_FLAG))
        assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
        assertTrue("Paragraph should use the available height", layout.height >= 190)
        assertTrue("Readable font should not be rejected for trailing spaces", layout.paint.textSize >= 28)
        assertTrue(layout.height <= 244)
        assertTrue((0 until layout.lineCount).all { layout.getLineMax(it) <= 384.5f })
    }
    @Test fun raggedParagraphUsesFullFrameAndLargerReadableTextWithoutClippingToInkContour() {
        val source = Bitmap.createBitmap(460, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val bounds = PixelRect(30f, 30f, 430f, 290f)
        val contour = listOf(PixelPoint(30f,30f), PixelPoint(430f,30f), PixelPoint(430f,100f),
            PixelPoint(170f,100f), PixelPoint(170f,290f), PixelPoint(30f,290f))
        val caption = PageTextRegion("paragraph", RegionKind.FREE_TEXT, bounds, contour, "", emptyList())
        val bubble = caption.copy(id = "bubble", kind = RegionKind.BUBBLE)
        val text = "A long paragraph with several sentences. It should fill the available frame and stay readable.\nThe second paragraph keeps its line break."
        val settings = BubbleRenderSettings(opacityPercent=100,fillMode = BubbleFillMode.WHITE, freeTextMaskExpansionPercent = 0)
        val renderer = BubbleMaskRenderer()
        val freeOverlay = renderer.prepareSource(source, listOf(caption)).layout(listOf(PageTranslatedRegion(caption, text)), settings)
        val bubbleOverlay = renderer.prepareSource(source, listOf(bubble)).layout(listOf(PageTranslatedRegion(bubble, text)), settings)
        val live = source.copy(Bitmap.Config.ARGB_8888, true)
        val export = renderer.render(source, listOf(PageTranslatedRegion(caption, text)), settings)
        val oldSpace = source.copy(Bitmap.Config.ARGB_8888, true)
        try {
            freeOverlay.draw(Canvas(live)); bubbleOverlay.draw(Canvas(oldSpace))
            assertTrue(live.sameAs(export))
            fun ink(bitmap: Bitmap) = (30 until 290).sumOf { y -> (30 until 430).count { x -> Color.red(bitmap.getPixel(x,y)) < 80 } }
            assertTrue("Full caption frame must increase readable glyph area", ink(live) > ink(oldSpace) * 1.3)
            assertTrue("New wrapping should extend beyond the original ink contour", (110 until 280).any { y -> (190 until 420).any { x -> Color.red(live.getPixel(x,y)) < 80 } })
            assertEquals(Color.WHITE, live.getPixel(5,5))
            assertEquals(Color.WHITE, source.getPixel(100,100))
            androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { directory ->
                java.io.File(directory).mkdirs()
                listOf("free-text-full-frame" to live, "free-text-old-inscribed-frame" to oldSpace).forEach { (name, bitmap) ->
                    java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        } finally { source.recycle(); live.recycle(); export.recycle(); oldSpace.recycle() }
    }
    @Test fun maskExpansionRedrawsExistingTranslationFromSamePreparedSource() {
        val source=Bitmap.createBitmap(180,140,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        val region=PageTextRegion("caption",RegionKind.FREE_TEXT,PixelRect(40f,40f,140f,100f),emptyList(),"TEXT",emptyList())
        val items=listOf(PageTranslatedRegion(region,"译文"))
        val seed=BubbleMaskRenderer().prepareSource(source,listOf(region))
        val small=source.copy(Bitmap.Config.ARGB_8888,true); val large=source.copy(Bitmap.Config.ARGB_8888,true)
        try {
            seed.layout(items,BubbleRenderSettings(opacityPercent=100,fillMode=BubbleFillMode.WHITE,freeTextMaskExpansionPercent=0)).draw(Canvas(small))
            seed.layout(items,BubbleRenderSettings(opacityPercent=100,fillMode=BubbleFillMode.WHITE,freeTextMaskExpansionPercent=15)).draw(Canvas(large))
            assertEquals(Color.CYAN,small.getPixel(35,70)); assertEquals(Color.WHITE,large.getPixel(35,70))
            assertEquals(Color.CYAN,source.getPixel(35,70))
        } finally { source.recycle(); small.recycle(); large.recycle() }
    }
    @Test fun emptyTranslationLeavesMisdetectedArtworkVisibleInReaderAndExport() {
        val original=Bitmap.createBitmap(160,120,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        Canvas(original).drawLine(0f,0f,160f,120f,Paint().apply { color=Color.BLACK; strokeWidth=4f })
        val region=PageTextRegion("false-detection",RegionKind.FREE_TEXT,PixelRect(20f,20f,140f,100f),emptyList(),"",emptyList())
        val items=listOf(PageTranslatedRegion(region,""))
        val renderer=BubbleMaskRenderer()
        val export=renderer.render(original,items,BubbleRenderSettings(opacityPercent=100))
        val live=original.copy(Bitmap.Config.ARGB_8888,true)
        try {
            val overlay=renderer.prepareSource(original,listOf(region)).layout(items,BubbleRenderSettings(opacityPercent=100),hideEmpty=true)
            overlay.draw(Canvas(live))
            assertTrue(original.sameAs(export)); assertTrue(export.sameAs(live))
            assertNull(overlay.hitTest(80f,60f))
        } finally { original.recycle(); export.recycle(); live.recycle() }
    }
    @Test fun freeTextSamplesOutsideEvenWithoutOcrAndPreservesIrregularCorners() {
        val background = Color.rgb(120,160,180)
        val original = Bitmap.createBitmap(240,240,Bitmap.Config.ARGB_8888).apply { eraseColor(background) }
        val contour = listOf(PixelPoint(40f,40f),PixelPoint(180f,40f),PixelPoint(180f,100f),
            PixelPoint(100f,100f),PixelPoint(100f,180f),PixelPoint(40f,180f))
        val ink = Path().apply { moveTo(40f,40f); contour.drop(1).forEach { lineTo(it.x,it.y) }; close() }
        Canvas(original).drawPath(ink,Paint().apply { color=Color.BLACK })
        // A stroke extending beyond the segmentation boundary must also be hidden.
        Canvas(original).drawRect(37f,70f,43f,85f,Paint().apply { color=Color.BLACK })
        val region = PageTextRegion("free",RegionKind.FREE_TEXT,PixelRect(40f,40f,180f,180f),contour,"",emptyList())
        val renderer = BubbleMaskRenderer()
        fun mask(settings: BubbleRenderSettings) = original.copy(Bitmap.Config.ARGB_8888,true).also { output ->
            renderer.prepareSource(original,listOf(region)).layout(listOf(PageTranslatedRegion(region,"")),settings,hideEmpty=false).draw(Canvas(output))
        }
        val result = mask(BubbleRenderSettings(opacityPercent=100))
        val white = mask(BubbleRenderSettings(opacityPercent=100,fillMode=BubbleFillMode.WHITE))
        try {
            assertEquals(background,result.getPixel(60,60))
            assertEquals(background,result.getPixel(38,75))
            assertEquals(background,result.getPixel(10,10))
            assertEquals(background,white.getPixel(165,165)) // do not fill the absent corner
            assertEquals(Color.WHITE,white.getPixel(60,60))
            assertEquals(Color.BLACK,original.getPixel(60,60))
        } finally { result.recycle(); white.recycle(); original.recycle() }
    }
    @Test fun fontAndBoldRedrawSameTextWithoutChangingOriginal() {
        val original = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val region = PageTextRegion("font-test", RegionKind.BUBBLE, PixelRect(20f, 20f, 300f, 180f), emptyList(), "hello", emptyList())
        val translated = listOf(PageTranslatedRegion(region, "Hello world"))
        val renderer = BubbleMaskRenderer()
        val normal = renderer.render(original, translated, BubbleRenderSettings(opacityPercent=100))
        val changed = renderer.render(original, translated, BubbleRenderSettings(opacityPercent=100,font = BubbleFont.MONOSPACE, fontScalePercent = 80, bold = true))
        try {
            assertFalse(normal.sameAs(changed))
            assertEquals(Color.WHITE, original.getPixel(160, 100))
            assertEquals(Color.WHITE, changed.getPixel(0, 0))
        } finally { normal.recycle(); changed.recycle(); original.recycle() }
    }
    @Test fun preparedOverlaySurvivesSourceReleaseAndMatchesExportPixels() {
        val original=Bitmap.createBitmap(300,200,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
        val region=PageTextRegion("p",RegionKind.BUBBLE,PixelRect(20f,20f,280f,180f),emptyList(),"hello",emptyList())
        val items=listOf(PageTranslatedRegion(region,"你好 🙂"))
        val renderer=BubbleMaskRenderer();val seed=renderer.prepareSource(original,listOf(region))
        val export=renderer.render(original,items,BubbleRenderSettings(opacityPercent=100))
        val live=original.copy(Bitmap.Config.ARGB_8888,true);original.recycle()
        try {
            val overlay=seed.layout(items,BubbleRenderSettings(opacityPercent=100))
            overlay.draw(Canvas(live))
            assertTrue(export.sameAs(live))
            assertEquals("p",overlay.hitTest(150f,100f));assertNull(overlay.hitTest(5f,5f))
            assertNull(seed.layout(emptyList(),BubbleRenderSettings(opacityPercent=100)).hitTest(150f,100f))
        } finally {export.recycle();live.recycle()}
    }
    @Test fun masksAndTextLeaveOriginalAndOutsidePixelsUntouched() {
        val original=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.CYAN)}
        val canvas=Canvas(original);canvas.drawRect(40f,40f,360f,280f,Paint().apply {color=Color.WHITE})
        canvas.drawText("ORIGINAL",80f,160f,Paint().apply {color=Color.BLACK;textSize=38f})
        val pixels=IntArray(160000);original.getPixels(pixels,0,400,0,0,400,400)
        val region=PageTextRegion("p",RegionKind.BUBBLE,PixelRect(40f,40f,360f,280f),listOf(PixelPoint(40f,40f),PixelPoint(360f,40f),PixelPoint(360f,280f),PixelPoint(40f,280f)),"ORIGINAL",listOf(PixelRect(70f,110f,330f,170f)))
        val result=BubbleMaskRenderer().render(original,listOf(PageTranslatedRegion(region,"你好世界")),BubbleRenderSettings(opacityPercent=100))
        try {
            val after=IntArray(160000);original.getPixels(after,0,400,0,0,400,400);assertArrayEquals(pixels,after)
            assertEquals(Color.CYAN,result.getPixel(20,20));assertEquals(Color.CYAN,result.getPixel(200,320))
            assertEquals(Color.WHITE,result.getPixel(60,60))
            val rendered=IntArray(160000);result.getPixels(rendered,0,400,0,0,400,400);assertFalse(pixels.contentEquals(rendered))
        } finally {original.recycle();result.recycle()}
    }
    @Test fun longUnicodeTranslationFitsWithoutDroppingItsEnd() {
        val text="你好世界。".repeat(60)+"最后一句😀"
        val layout=BubbleMaskRenderer().fitLayout(text,180,140,TextPaint(Paint.ANTI_ALIAS_FLAG))
        assertEquals(text.length,layout.getLineEnd(layout.lineCount-1));assertTrue(layout.height<=140)
        assertTrue((0 until layout.lineCount).all {layout.getLineWidth(it)<=180.5f})
    }
    @Test fun maximumEditableTextInTinyRegionDoesNotCrashOrTruncate() {
        val text="译文🙂".repeat(3000)
        val layout=BubbleMaskRenderer().fitLayout(text,1,1,TextPaint(Paint.ANTI_ALIAS_FLAG))
        assertEquals(text.length,layout.getLineEnd(layout.lineCount-1));assertTrue(layout.height<=1)
    }
    @Test fun blackBubbleAndOpacitySettingsAreHonored() {
        val original=Bitmap.createBitmap(300,200,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLACK)}
        val region=PageTextRegion("p",RegionKind.BUBBLE,PixelRect(10f,10f,290f,190f),emptyList(),"old",emptyList())
        val renderer=BubbleMaskRenderer()
        val auto=renderer.render(original,listOf(PageTranslatedRegion(region,"你好")),BubbleRenderSettings(opacityPercent=100))
        val half=renderer.render(original,listOf(PageTranslatedRegion(region,"你好")),BubbleRenderSettings(BubbleFillMode.WHITE,50,8))
        try {
            assertEquals(Color.BLACK,auto.getPixel(25,25))
            assertTrue(Color.red(half.getPixel(25,25)) in 120..135)
            assertTrue((0 until auto.width).any {x -> (0 until auto.height).any {y -> Color.red(auto.getPixel(x,y))>220}})
        } finally {original.recycle();auto.recycle();half.recycle()}
    }
    @Test fun ocrFallbackSamplesBlackBackgroundOutsideItsTextBox() {
        val original=Bitmap.createBitmap(300,200,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLACK)}
        val bounds=PixelRect(20f,40f,280f,160f)
        val region=PageTextRegion("p",RegionKind.FREE_TEXT,bounds,emptyList(),"old",listOf(bounds))
        val result=BubbleMaskRenderer().render(original,listOf(PageTranslatedRegion(region,"你好")),BubbleRenderSettings(opacityPercent=100))
        try {assertEquals(Color.BLACK,result.getPixel(35,55));assertTrue(Color.red(result.getPixel(150,110))>0 ||
            (40 until 160).any {y -> (20 until 280).any {x ->Color.red(result.getPixel(x,y))>220}})}
        finally {original.recycle();result.recycle()}
    }
    @Test fun bubbleMaskInsetPreservesInkOnItsContour() {
        val original=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
        Canvas(original).drawRect(40f,40f,360f,280f,Paint().apply {color=Color.BLACK;style=Paint.Style.STROKE;strokeWidth=4f})
        val region=PageTextRegion("p",RegionKind.BUBBLE,PixelRect(40f,40f,360f,280f),listOf(
            PixelPoint(40f,40f),PixelPoint(360f,40f),PixelPoint(360f,280f),PixelPoint(40f,280f)),"OLD",emptyList())
        val result=BubbleMaskRenderer().render(original,listOf(PageTranslatedRegion(region,"你好")),BubbleRenderSettings(opacityPercent=100))
        try {assertEquals(Color.BLACK,result.getPixel(200,41));assertEquals(Color.BLACK,result.getPixel(41,100))}
        finally {original.recycle();result.recycle()}
    }
    @Test fun inaccurateContourStillPreservesOutlineOutsideOcrBoxes() {
        val original=Bitmap.createBitmap(400,400,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
        Canvas(original).drawRect(40f,40f,360f,280f,Paint().apply {color=Color.BLACK;style=Paint.Style.STROKE;strokeWidth=4f})
        val region=PageTextRegion("p",RegionKind.BUBBLE,PixelRect(10f,10f,390f,310f),listOf(
            PixelPoint(10f,10f),PixelPoint(390f,10f),PixelPoint(390f,310f),PixelPoint(10f,310f)),"OLD",listOf(PixelRect(120f,130f,280f,170f)))
        val result=BubbleMaskRenderer().render(original,listOf(PageTranslatedRegion(region,"你好")),BubbleRenderSettings(opacityPercent=100))
        try {assertEquals(Color.BLACK,result.getPixel(200,40));assertEquals(Color.BLACK,result.getPixel(40,100))}
        finally {original.recycle();result.recycle()}
    }
}
