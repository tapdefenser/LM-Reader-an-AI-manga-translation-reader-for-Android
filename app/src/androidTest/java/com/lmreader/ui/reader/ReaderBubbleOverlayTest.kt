package com.lmreader.ui.reader

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.model.*
import com.lmreader.core.storage.reader.*
import com.lmreader.core.vision.BubbleMaskRenderer
import com.lmreader.ui.reader.translation.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReaderBubbleOverlayTest {
    @Test fun untranslatedBubbleCanBeSelectedForEditingWithoutCoveringOriginalText() {
        val image=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
        Canvas(image).drawRect(30f,30f,70f,70f,Paint().apply {color=Color.BLACK})
        val region=PageTextRegion("empty",RegionKind.BUBBLE,PixelRect(10f,10f,90f,90f),emptyList(),"",emptyList())
        val seed=BubbleMaskRenderer().prepareSource(image,listOf(region))
        val regions=listOf(PageTranslatedRegion(region,""))
        val normal=seed.layout(regions,BubbleRenderSettings(),hideEmpty=true)
        val editable=seed.layout(regions,BubbleRenderSettings(),hideEmpty=true,includeEmptyForEditing=true)
        val drawn=image.copy(Bitmap.Config.ARGB_8888,true)
        try {
            assertNull(normal.hitTest(50f,50f))
            assertEquals("empty",editable.hitTest(50f,50f))
            editable.draw(Canvas(drawn))
            assertTrue(image.sameAs(drawn))
            editable.drawEditing(Canvas(drawn),"empty",2f)
            assertFalse(image.sameAs(drawn))
        } finally {drawn.recycle();image.recycle()}
    }
    @Test fun streamingTextReusesMaskGeometryAfterOriginalReleaseAndLeavesPendingBubbleVisible() {
        val original = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        Canvas(original).drawRect(240f, 70f, 280f, 110f, Paint().apply { color = Color.BLACK })
        val first = PageTextRegion("first", RegionKind.BUBBLE, PixelRect(10f, 10f, 190f, 190f), emptyList(), "", emptyList())
        val second = PageTextRegion("second", RegionKind.BUBBLE, PixelRect(210f, 10f, 390f, 190f), emptyList(), "", emptyList())
        val seed = BubbleMaskRenderer().prepareSource(original, listOf(first, second))
        val preview = original.copy(Bitmap.Config.ARGB_8888, true)
        val complete = original.copy(Bitmap.Config.ARGB_8888, true)
        original.recycle()
        try {
            val partial = listOf(PageTranslatedRegion(first.copy(sourceText = "one"), "一"), PageTranslatedRegion(second, ""))
            seed.layout(partial, BubbleRenderSettings(), hideEmpty = true).draw(Canvas(preview))
            assertEquals(Color.BLACK, preview.getPixel(260, 90))
            val finished = listOf(partial.first(), PageTranslatedRegion(second.copy(sourceText = "two"), "二"))
            seed.layout(finished, BubbleRenderSettings(), hideEmpty = true).draw(Canvas(complete))
            assertEquals(first.renderGeometry(), finished.first().region.renderGeometry())
            assertFalse(preview.sameAs(complete))
            val edited = seed.layout(listOf(PageTranslatedRegion(first.copy(bounds = PixelRect(20f, 20f, 180f, 180f)), "一")), BubbleRenderSettings())
            assertNull(edited.hitTest(15f, 15f))
            assertEquals(first.id, edited.hitTest(100f, 100f))
        } finally { preview.recycle(); complete.recycle() }
    }
    private val context=ApplicationProvider.getApplicationContext<android.content.Context>()
    private val root=File(context.cacheDir,"overlay-fixture-"+UUID.randomUUID()).apply {mkdirs()}
    private val input=File(root,"source.png")
    private val page=ReaderPage("overlay-fixture",0,"source.png","source.png")
    private val source=object: PageSource {
        override suspend fun pages()=listOf(page)
        override suspend fun open(page: ReaderPage)=input.inputStream()
        override suspend fun probe(page: ReaderPage)=PageGeometry(400,500)
    }
    @After fun cleanup() {check(root.canonicalFile.parentFile==context.cacheDir.canonicalFile);root.deleteRecursively()}
    @Test fun entirelyWhitePageCanStillBeDisplayedWhenCropBordersIsEnabled() = runBlocking {
        val original=Bitmap.createBitmap(400,500,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
        input.outputStream().use {assertTrue(original.compress(Bitmap.CompressFormat.PNG,100,it))};original.recycle()
        val saved=ReaderPageTranslation(page.pageId,"fixture",File(root,"data.json"),LocalTranslationLanguage.ENGLISH,
            LocalTranslationLanguage.CHINESE_SIMPLIFIED,ReaderPageArtifactStore.hashFile(input),400,500,emptyList(),emptyList(),0)
        val prepared=prepareOverlayPage(context,source,page,saved,0,true)
        try {assertEquals(Color.WHITE,prepared.bitmap.getPixel(prepared.bitmap.width/2,prepared.bitmap.height/2))}
        finally {prepared.bitmap.recycle()}
    }
    @Test fun persistedAnalysisCoordinatesSurviveDecoderRoundingChangeWithoutCreatingImage() = runBlocking {
        val original = Bitmap.createBitmap(400, 500, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        input.outputStream().use { original.compress(Bitmap.CompressFormat.PNG, 100, it) }; original.recycle()
        val before = input.readBytes()
        val regions = listOf(PageTranslatedRegion(PageTextRegion("old-geometry", RegionKind.BUBBLE,
            PixelRect(50f, 60f, 300f, 400f), emptyList(), "HELLO", emptyList()), "你好"))
        val saved = ReaderPageTranslation(page.pageId, "fixture", File(root, "data.json"), LocalTranslationLanguage.ENGLISH,
            LocalTranslationLanguage.CHINESE_SIMPLIFIED, ReaderPageArtifactStore.hashFile(input), 399, 499, regions, emptyList(), 0)
        prepareOverlaySource(context, source, page, saved).layout(regions, BubbleRenderSettings())
        val prepared = prepareOverlayPage(context, source, page, saved, 0, false)
        try { assertEquals(399, prepared.geometry.width); assertEquals(499, prepared.geometry.height) }
        finally { prepared.bitmap.recycle() }
        assertArrayEquals(before, input.readBytes())
        assertEquals(listOf("source.png"), root.listFiles().orEmpty().map { it.name })
    }
    @Test fun displaySamplingAndFullResolutionShareTheSameAnalysisCoordinates() = runBlocking {
        val original=Bitmap.createBitmap(1600,2000,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
        Canvas(original).drawRect(80f,100f,1520f,1900f,Paint().apply {color=Color.BLACK;style=Paint.Style.STROKE;strokeWidth=8f})
        input.outputStream().use {assertTrue(original.compress(Bitmap.CompressFormat.PNG,100,it))};original.recycle()
        val saved=ReaderPageTranslation(page.pageId,"fixture",File(root,"data.json"),LocalTranslationLanguage.ENGLISH,
            LocalTranslationLanguage.CHINESE_SIMPLIFIED,ReaderPageArtifactStore.hashFile(input),1600,2000,emptyList(),emptyList(),0)
        val sampled=prepareOverlayPage(context,source,page,saved,600,true)
        val full=prepareOverlayPage(context,source,page,saved,0,true)
        try {
            assertTrue(full.bitmap.width>sampled.bitmap.width);assertTrue(full.bitmap.height>sampled.bitmap.height)
            for(prepared in listOf(sampled,full)) {
                assertEquals(1600,prepared.geometry.width);assertEquals(2000,prepared.geometry.height)
                val crop=prepared.geometry.crop
                assertEquals(PixelPoint(0f,0f),prepared.geometry.enginePoint(crop.left,crop.top,prepared.bitmap.width,prepared.bitmap.height))
            }
        } finally {sampled.bitmap.recycle();full.bitmap.recycle()}
    }
    @Test fun originalOnlyBitmapAndNativeCropShareExactCoordinatesWithLiveOverlayAndExport() = runBlocking {
        val original=Bitmap.createBitmap(400,500,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
        val pen=Paint().apply {color=Color.BLACK;style=Paint.Style.STROKE;strokeWidth=4f}
        Canvas(original).apply {drawRect(40f,50f,360f,450f,pen)
            drawText("HELLO",120f,220f,Paint().apply {color=Color.BLACK;textSize=32f})}
        input.outputStream().use {assertTrue(original.compress(Bitmap.CompressFormat.PNG,100,it))}
        val originalBytes=input.readBytes()
        val regions=listOf(PageTranslatedRegion(PageTextRegion(page.pageId+":a",RegionKind.BUBBLE,
            PixelRect(90f,130f,320f,300f),emptyList(),"HELLO",listOf(PixelRect(110f,180f,300f,230f))),"你好"))
        val saved=ReaderPageTranslation(page.pageId,"fixture",File(root,"data.json"),LocalTranslationLanguage.ENGLISH,
            LocalTranslationLanguage.CHINESE_SIMPLIFIED,ReaderPageArtifactStore.hashFile(input),400,500,regions,emptyList(),0)
        val prepared=prepareOverlayPage(context,source,page,saved,0,true)
        val crop=prepared.geometry.crop
        val expectedOriginal=Bitmap.createBitmap(original,crop.left.toInt(),crop.top.toInt(),crop.width.toInt(),crop.height.toInt())
        val rendered=BubbleMaskRenderer().render(original,regions,BubbleRenderSettings())
        val expected=Bitmap.createBitmap(rendered,crop.left.toInt(),crop.top.toInt(),crop.width.toInt(),crop.height.toInt())
        val live=prepared.bitmap.copy(Bitmap.Config.ARGB_8888,true)
        try {
            assertTrue(crop.left>0 && crop.top>0)
            assertTrue(expectedOriginal.sameAs(prepared.bitmap))
            Canvas(live).apply {translate(-crop.left,-crop.top);prepared.overlay.layout(regions,BubbleRenderSettings()).draw(this)}
            assertTrue(expected.sameAs(live))
            assertArrayEquals(originalBytes,input.readBytes())
            assertEquals(listOf("source.png"),root.listFiles().orEmpty().map {it.name})
        } finally {
            live.recycle();prepared.bitmap.recycle()
            if(expected !== rendered) expected.recycle()
            if(expectedOriginal !== original) expectedOriginal.recycle()
            rendered.recycle();original.recycle()
        }
    }
}
